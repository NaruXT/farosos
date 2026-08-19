plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // X25519 efímero (#61) — mismo motivo que en :codec (java.security no
    // soporta esto de forma confiable a minSdk 26). AES-GCM en cambio usa
    // javax.crypto nativo de la plataforma, sin necesitar BouncyCastle para eso.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
}

tasks.test {
    useJUnitPlatform()
}
