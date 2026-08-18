plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

// El plugin de Google Services falla la CONFIGURACIÓN de todo el build
// multi-módulo (no solo de :app) si `google-services.json` no existe — y
// Gradle configura todos los subproyectos aunque se apunte a una sola
// tarea de otro módulo (p. ej. `gradle :codec:test`). Aplicarlo solo si el
// archivo real ya está — ver android/app/README.md para el paso manual
// (registrar la app en la consola de Firebase, descargar el archivo).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
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
    implementation(project(":participantregistration"))
    implementation(project(":deviceidentity"))
    implementation("androidx.security:security-crypto:1.1.0")
    // Ed25519 no está disponible de forma confiable vía java.security en
    // Android (ni el provider de plataforma ni Play Services lo agregan;
    // AndroidKeyStore lo soporta recién desde API 33, y solo con backing de
    // hardware) — BouncyCastle lo implementa en software para todo minSdk 26+.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.0")
}
