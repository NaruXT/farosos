plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // Solo para el test de plumbing que lee spec/test-vectors.json; el módulo
    // Android real puede seguir usando su propio parser JSON (org.json viene
    // incluido en la plataforma Android).
    testImplementation("org.json:json:20240303")
    // ECDH de Caso B (X25519) — mismo motivo que Ed25519 en :app/DeviceIdentity
    // (#41): java.security no soporta esto de forma confiable a minSdk 26.
    // Ver project_farosos_android_ed25519_bouncycastle (memoria de sesión).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}

tasks.test {
    useJUnitPlatform()
}
