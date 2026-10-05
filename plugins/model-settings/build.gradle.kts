plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

apply(from = rootProject.file("gradle/feature-ui-texts.gradle.kts"))

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain { kotlin.srcDir(layout.buildDirectory.dir("generated/uiTexts/kotlin")) }
        commonMain.dependencies {
            api(project(":plugins:api"))
            implementation(compose.material3)
            implementation(compose.foundation)
            api(project(":plugins:default-ui-api"))
            implementation(project(":libraries:ui"))
            implementation(compose.runtime)
        }
        commonTest.dependencies {
            implementation(project(":plugins:test-support"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.modelsettings"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
