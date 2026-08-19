pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "farosos-android"

include(":codec")
include(":deviceidentity")
include(":personstate")
include(":networkrole")
include(":beaconradio")
include(":participantregistration")
include(":caseresolution")
include(":directchat")
include(":app")
