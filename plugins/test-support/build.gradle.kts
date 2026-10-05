plugins {
    kotlin("multiplatform")
    id("com.android.library")
}
kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":plugins:api"))
            api(project(":plugins:default-ui-api"))
            implementation(project(":plugins:ui-contributions"))
            implementation(project(":plugins:default-ui-bridge"))
        }
    }
}
android {
    namespace = "ai.meteor.kcode.test"
    compileSdk = 35
    defaultConfig { minSdk = 35 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
