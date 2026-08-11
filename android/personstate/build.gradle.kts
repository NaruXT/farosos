plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":codec"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
