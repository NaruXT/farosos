plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "com.farosos.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.farosos.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":codec"))
    implementation(project(":personstate"))
    implementation(project(":networkrole"))
    implementation(project(":beaconradio"))
    implementation("androidx.security:security-crypto:1.1.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
}
