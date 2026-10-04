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
        commonMain.dependencies { api(project(":plugins:api")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidMain.dependencies {
            implementation("dev.rikka.shizuku:api:13.1.5")
            implementation("dev.rikka.shizuku:provider:13.1.5")
            implementation("org.apache.commons:commons-compress:1.27.1")
            implementation("org.tukaani:xz:1.10")
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
    }
}
android {
    namespace = "ai.meteor.kcode.plugin.nativeexecution"
    compileSdk = 35
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
