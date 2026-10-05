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
            implementation(project(":plugins:test-support"))
            implementation(project(":plugins:web-search"))
            implementation(project(":plugins:llm-core"))
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        getByName("desktopMain").dependencies { implementation("androidx.datastore:datastore-preferences:1.2.1") }
        androidMain.dependencies { implementation("com.tencent:mmkv-kmp:2.4.1") }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.test:runner:1.6.2")
        }
    }
}
android {
    namespace = "ai.meteor.kcode.plugin.settings"
    compileSdk = 35
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
