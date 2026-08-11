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
}

tasks.test {
    useJUnitPlatform()
}
