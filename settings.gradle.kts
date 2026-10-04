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
include(":apps:androidApp")
include(":apps:desktopApp")
include(":plugins:agent-loop")
include(":plugins:api")
include(":plugins:application")
include(":plugins:artifact-tools")
include(":plugins:artifact-repository")
include(":plugins:capability-providers")
include(":plugins:continuations")
include(":plugins:filesystem")
include(":plugins:goal")
include(":plugins:interaction")
include(":plugins:inventory")
include(":plugins:llm")
include(":plugins:platform-android")
include(":plugins:platform-desktop")
include(":plugins:runtime")
include(":plugins:schedule")
include(":plugins:shell")
include(":plugins:skill-tools")
include(":plugins:skills")
include(":plugins:subagent-provider")
include(":plugins:subagents")
include(":plugins:system-prompt")
include(":plugins:tools")
include(":plugins:ui-pages")
include(":plugins:ui-settings")
include(":plugins:session-history")
include(":plugins:conversation-execution")
include(":plugins:conversation-export")
include(":plugins:schedule-dispatch")
include(":plugins:web-container")
include(":plugins:web-search")
include(":plugins:web-search-provider")
include(":plugins:bundle-native")
include(":plugins:installation-store")

include(":plugins:goal-ui")

include(":plugins:conversation-overlay")

include(":plugins:settings-commands")

include(":plugins:history-repository")

include(":plugins:test-support")

include(":plugins:settings-repository")

include(":plugins:native-execution")

include(":plugins:native-filesystem")

include(":plugins:native-notifications")

include(":plugins:message-codec")
include(":plugins:model-settings")
include(":plugins:localization")

include(":plugins:markdown")

include(":plugins:default-ui-api")

include(":libraries:ui")
