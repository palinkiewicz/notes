plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Nothing: the PDF object layer is java.util.zip and arithmetic, and keeping it that way is what
    // keeps a PDF library out of a five-megabyte APK.
    testImplementation(libs.junit)
}
