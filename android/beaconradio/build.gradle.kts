plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":codec"))
    implementation(project(":personstate"))
    testImplementation(kotlin("test"))
    // Genera keypairs Ed25519 de prueba en SignatureFragmentAssemblerTest —
    // :codec ya depende de esto (`implementation`, no expuesto a
    // consumidores), así que hace falta declararlo aparte acá para el
    // classpath de test. Misma versión que :codec (#44/#45).
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.80")
}

tasks.test {
    useJUnitPlatform()
}
