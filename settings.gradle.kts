pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
    }
}

rootProject.name = "kcode"
include(":shared")
include(":apps:androidApp")
include(":apps:web:sqliteWasmWorker")
include(":extensions:webContainer")
