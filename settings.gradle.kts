pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            name = "MavenCentralSnapshots"
            content {
                includeGroup("io.github.meteor149")
            }
        }
        maven("https://maven.aliyun.com/repository/central") {
            name = "AliyunCentralMirrorForHaze"
            content {
                includeGroup("dev.chrisbanes.haze")
            }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "kcode"
include(":shared")
include(":apps:androidApp")
include(":apps:desktopApp")
include(":apps:web:sqliteWasmWorker")
include(":extensions:webContainer")
include(":plugins:api")
include(":plugins:application")
include(":plugins:inventory")
include(":plugins:tools")
include(":plugins:system-prompt")
include(":plugins:llm")
include(":plugins:continuations")
include(":plugins:interaction")
include(":plugins:skills")
include(":plugins:subagents")
include(":plugins:goal")
include(":plugins:schedule")
include(":plugins:agent-loop")
include(":plugins:runtime")
include(":plugins:platform-desktop")
include(":plugins:platform-android")
include(":plugins:filesystem")
include(":plugins:shell")
include(":plugins:web-container")
include(":plugins:web-search")
include(":plugins:skill-tools")
include(":plugins:artifact-tools")
