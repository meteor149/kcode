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
            api(project(":plugins:profiles"))
            implementation(project(":plugins:default-ui-api"))
            implementation(project(":libraries:ui"))
            implementation(project(":plugins:installation-store"))
            compileOnly(project(":plugins:history-repository"))
            compileOnly(project(":plugins:settings"))
            compileOnly(project(":plugins:web-search"))
            compileOnly(project(":plugins:application"))
            compileOnly(project(":plugins:conversation-overlay"))
            compileOnly(project(":plugins:ui-pages"))
            compileOnly(project(":plugins:ui-messages"))
            compileOnly(project(":plugins:ui-theme"))
            compileOnly(project(":plugins:ui-shell"))
            compileOnly(project(":plugins:ui-profiles"))
            compileOnly(project(":plugins:ui-contributions"))
            compileOnly(project(":plugins:default-ui-bridge"))
            compileOnly(project(":plugins:markdown"))
            compileOnly(project(":plugins:message-codec"))
            compileOnly(project(":plugins:llm-core"))
            compileOnly(project(":plugins:localization"))
            compileOnly(project(":plugins:session-history"))
            compileOnly(project(":plugins:conversation-execution"))
            compileOnly(project(":plugins:conversation-export"))
            compileOnly(project(":plugins:native-notifications"))
            compileOnly(project(":plugins:tools"))
            compileOnly(project(":plugins:system-prompt"))
            compileOnly(project(":plugins:llm:openai"))
            compileOnly(project(":plugins:llm:azure-openai"))
            compileOnly(project(":plugins:llm:anthropic"))
            compileOnly(project(":plugins:llm:google"))
            compileOnly(project(":plugins:llm:deepseek"))
            compileOnly(project(":plugins:llm:openrouter"))
            compileOnly(project(":plugins:llm:bedrock"))
            compileOnly(project(":plugins:llm:mistral"))
            compileOnly(project(":plugins:llm:alibaba"))
            compileOnly(project(":plugins:llm:ollama"))
            compileOnly(project(":plugins:llm:glm"))
            compileOnly(project(":plugins:continuations"))
            compileOnly(project(":plugins:skills"))
            compileOnly(project(":plugins:subagents"))
            compileOnly(project(":plugins:goal"))
            compileOnly(project(":plugins:interaction"))

            compileOnly(project(":plugins:schedule"))
            compileOnly(project(":plugins:agent-loop"))
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
