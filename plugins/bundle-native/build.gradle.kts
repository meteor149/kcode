plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":plugins:api"))
            implementation(project(":plugins:installation-store"))
            implementation(project(":plugins:history-repository"))
            implementation(project(":plugins:settings-repository"))
            implementation(project(":plugins:settings-commands"))
            implementation(project(":plugins:web-search-provider"))
            implementation(project(":plugins:application"))
            implementation(project(":plugins:web-container"))
            implementation(project(":plugins:artifact-repository"))
            implementation(project(":plugins:conversation-overlay"))
            implementation(project(":plugins:ui-pages"))
            implementation(project(":plugins:ui-settings"))
            implementation(project(":plugins:markdown"))
            implementation(project(":plugins:message-codec"))
            implementation(project(":plugins:model-settings"))
            implementation(project(":plugins:localization"))
            implementation(project(":plugins:session-history"))
            implementation(project(":plugins:conversation-execution"))
            implementation(project(":plugins:conversation-export"))
            implementation(project(":plugins:native-notifications"))
            implementation(project(":plugins:schedule-dispatch"))
            implementation(project(":plugins:tools"))
            implementation(project(":plugins:system-prompt"))
            implementation(project(":plugins:llm"))
            implementation(project(":plugins:continuations"))
            implementation(project(":plugins:interaction"))
            implementation(project(":plugins:skills"))
            implementation(project(":plugins:subagents"))
            implementation(project(":plugins:subagent-provider"))
            implementation(project(":plugins:goal"))
            implementation(project(":plugins:goal-ui"))
            implementation(project(":plugins:schedule"))
            implementation(project(":plugins:agent-loop"))
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.bundlenative"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
