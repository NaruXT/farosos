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
}

tasks.test {
    useJUnitPlatform()
}
