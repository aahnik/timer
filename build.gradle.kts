plugins {
    id("com.android.application") version "9.5.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
    namespace = "dev.aahnik.timer"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "dev.aahnik.timer"
        minSdk = 26
        targetSdk = 36
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
}
