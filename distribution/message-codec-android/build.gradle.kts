plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
}

android {
    namespace = "ai.meteor.kcode.external.messagecodec"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.meteor.kcode.external.messagecodec"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    sourceSets.getByName("main").java.srcDir(project(":plugins:message-codec").file("src/commonMain/kotlin"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly(project(":plugins:api"))
}
