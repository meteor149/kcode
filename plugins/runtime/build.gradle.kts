plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
                implementation(compose.runtime)
                implementation(project(":plugins:application"))
                api(project(":plugins:api"))
                implementation(project(":plugins:inventory"))
                implementation(project(":plugins:tools"))
                implementation(project(":plugins:system-prompt"))
                implementation(project(":plugins:llm"))
                implementation(project(":plugins:continuations"))
                implementation(project(":plugins:interaction"))
                implementation(project(":plugins:skills"))
                implementation(project(":plugins:subagents"))
                implementation(project(":plugins:goal"))
                implementation(project(":plugins:schedule"))
                implementation(project(":plugins:agent-loop"))
                api(libs.cordis.loader)
                implementation(libs.cordis.hmr)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.runtime"
    compileSdk = 35

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
