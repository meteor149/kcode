pluginManagement {
    providers.gradleProperty("cordisSource").orNull?.let { includeBuild(it) }
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            content {
                includeGroup("io.github.meteor149")
                includeGroup("io.github.meteor149.cordis.packager")
            }
        }
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
include(":plugins:interaction-settings")
include(":plugins:execution-settings")
include(":plugins:native-tool-approvals")
include(":plugins:inventory")
include(":plugins:llm")
include(":plugins:llm-service")
include(":plugins:platform-android")
include(":plugins:platform-desktop")
include(":plugins:runtime")
include(":plugins:schedule")
include(":plugins:shell")
include(":plugins:skill-tools")
include(":plugins:skills")
include(":plugins:subagents")
include(":plugins:system-prompt")
include(":plugins:tools")
include(":plugins:ui-pages")
include(":plugins:ui-contributions")
include(":plugins:default-ui-bridge")
include(":plugins:session-history")
include(":plugins:conversation-execution")
include(":plugins:conversation-export")
include(":plugins:web-container")
include(":plugins:web-search")
include(":plugins:bundle-native")
include(":plugins:installation-store")
include(":plugins:package-provider")
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
