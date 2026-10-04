plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 supplies built-in Kotlin; do not apply org.jetbrains.kotlin.android.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
