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
providers.gradleProperty("cordisSource").orNull?.let { includeBuild(it) }
include(":apps:androidApp")
include(":apps:desktopApp")
include(":plugins:agent-loop")
include(":plugins:api")
include(":plugins:application")
include(":plugins:capability-providers")
include(":plugins:continuations")
include(":plugins:filesystem")
include(":plugins:goal")
include(":plugins:interaction")
include(":plugins:native-execution")
include(":plugins:native-tool-approvals")
include(":plugins:inventory")
include(":plugins:llm:adapter-support")
include(":plugins:llm:openai")
include(":plugins:llm:azure-openai")
include(":plugins:llm:anthropic")
include(":plugins:llm:google")
include(":plugins:llm:deepseek")
include(":plugins:llm:openrouter")
include(":plugins:llm:bedrock")
include(":plugins:llm:mistral")
include(":plugins:llm:alibaba")
include(":plugins:llm:ollama")
include(":plugins:llm:glm")
include(":plugins:llm-core")
include(":plugins:platform-android")
include(":plugins:platform-desktop")
include(":plugins:runtime")
include(":plugins:schedule")
include(":plugins:shell")
include(":plugins:skills")
include(":plugins:subagents")
include(":plugins:system-prompt")
include(":plugins:tools")
include(":plugins:ui-pages")
include(":plugins:ui-messages")
include(":plugins:ui-theme")
include(":plugins:ui-shell")
include(":plugins:ui-contributions")
include(":plugins:default-ui-bridge")
include(":plugins:session-history")
include(":plugins:conversation-execution")
include(":plugins:conversation-export")
include(":plugins:web-search")
include(":plugins:bundle-native")
include(":plugins:installation-store")
include(":plugins:package-provider")
include(":plugins:conversation-overlay")

include(":plugins:settings")
include(":plugins:profiles")

include(":plugins:history-repository")

include(":plugins:test-support")




include(":plugins:native-notifications")

include(":plugins:message-codec")
include(":plugins:localization")

include(":plugins:markdown")

include(":plugins:default-ui-api")
include(":plugins:ui-profiles")

include(":libraries:ui")
