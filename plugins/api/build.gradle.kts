plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
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
            api("androidx.annotation:annotation:1.9.1")
            api(compose.runtime)
            api(compose.ui)
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api(libs.cordis.core)
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            api("ai.koog:agents-tools:1.1.1")
            api("ai.koog:koog-agents:1.1.1")
        }
        getByName("desktopMain").dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2") }
        getByName("androidMain").dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            api("com.tencent:mmkv-kmp:2.4.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

android {
    namespace = "ai.meteor.kcode.plugin.api"
    compileSdk = 35
    buildFeatures { aidl = true }
    packaging { jniLibs { useLegacyPackaging = true } }

    defaultConfig {
        minSdk = 35
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
